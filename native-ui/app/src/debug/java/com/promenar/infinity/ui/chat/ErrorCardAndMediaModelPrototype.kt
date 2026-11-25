package com.promenar.infinity.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.ScatterPlot
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillMenuButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.obsidianTopGlint
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityObsidianDialogChip
import com.promenar.infinity.ui.theme.InfinityObsidianDialogContainer
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors

/**
 * 高保真视觉原型 v1：
 * 1. 新版黑曜石极简聊天错误提示卡片（语义图标 + 主/副层级 + 边框胶囊折叠展开脱敏 HTTP/JSON 原始错误 + 复制 + 操作胶囊）
 * 2. 「编辑模型」补齐视频 / 音频类型、自定义接入点（如方舟 ep-...）云端媒体协议预设绑定与安全连接验证
 * 3. MiniMax / MiniMax Token Plan 与智谱 / Z.AI Coding Plan 国内外双区域四变体预设预览
 */

@Composable
private fun PrototypeFrame(
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
            modifier = Modifier.fillMaxSize(),
        ) {
            content()
        }
    }
}

@Composable
private fun PrototypeErrorCard(
    icon: ImageVector,
    title: String,
    providerSummary: String? = null,
    diagnosticChipLabel: String? = null,
    expanded: Boolean = false,
    diagnosticMeta: String? = null,
    diagnosticBody: String? = null,
    copyLabel: String = "复制",
    primaryAction: String? = null,
    secondaryAction: String? = null,
    showMoreMenu: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = MaterialTheme.infinityDomainColors.isDark
    val cardBg = if (dark) InfinityObsidianDialogContainer else scheme.surfaceContainerLowest
    val borderColor = if (dark) scheme.error.copy(alpha = 0.30f) else scheme.error.copy(alpha = 0.24f)
    val iconTileBg = if (dark) scheme.error.copy(alpha = 0.14f) else scheme.errorContainer.copy(alpha = 0.72f)
    val iconTint = if (dark) scheme.error else scheme.onErrorContainer

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        contentColor = scheme.onSurface,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .obsidianTopGlint(
                    color = if (dark) scheme.error.copy(alpha = 0.22f) else Color.Transparent,
                    cornerRadius = 16.dp,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(28.dp)
                        .background(iconTileBg, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = title,
                        style = InfinityTypography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                        ),
                        color = scheme.onSurface,
                    )
                    if (providerSummary != null) {
                        Text(
                            text = providerSummary,
                            style = InfinityTypography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (diagnosticChipLabel != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        onClick = {},
                        shape = RoundedCornerShape(14.dp),
                        color = if (dark) InfinityObsidianDialogChip else scheme.surfaceContainerHigh,
                        contentColor = scheme.onSurfaceVariant,
                        border = BorderStroke(
                            0.75.dp,
                            if (expanded) scheme.error.copy(alpha = 0.36f)
                            else scheme.outlineVariant.copy(alpha = if (dark) 0.5f else 0.7f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .heightIn(min = 28.dp)
                                .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = diagnosticChipLabel,
                                style = InfinityTypography.labelSmall.copy(
                                    fontFamily = if (diagnosticChipLabel.startsWith("HTTP")) InfinityMonospace else InfinityTypography.labelSmall.fontFamily,
                                ),
                                color = if (expanded) scheme.onSurface else scheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                            )
                            Icon(
                                imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }

            if (expanded && diagnosticBody != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (dark) Color(0xFF07080B) else scheme.surfaceContainerHigh,
                    contentColor = scheme.onSurfaceVariant,
                    border = BorderStroke(0.75.dp, scheme.outlineVariant.copy(alpha = if (dark) 0.4f else 0.65f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = diagnosticMeta.orEmpty(),
                                style = InfinityTypography.labelSmall.copy(fontFamily = InfinityMonospace),
                                color = scheme.error.copy(alpha = if (dark) 0.9f else 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Surface(
                                onClick = {},
                                shape = RoundedCornerShape(12.dp),
                                color = if (dark) InfinityObsidianDialogChip else scheme.surfaceContainerLowest,
                                contentColor = scheme.onSurface,
                                border = BorderStroke(0.75.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Text(
                                        text = copyLabel,
                                        style = InfinityTypography.labelSmall,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f))
                        Text(
                            text = diagnosticBody,
                            style = InfinityTypography.bodySmall.copy(
                                fontFamily = InfinityMonospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            ),
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (primaryAction != null || secondaryAction != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InfinityPillRow(modifier = Modifier.weight(1f), spacing = 8.dp) {
                        if (secondaryAction != null) {
                            InfinityPillButton(
                                text = secondaryAction,
                                onClick = {},
                                tone = InfinityPillTone.NEUTRAL,
                                compact = true,
                            )
                        }
                        if (primaryAction != null) {
                            InfinityPillButton(
                                text = primaryAction,
                                onClick = {},
                                tone = InfinityPillTone.PRIMARY,
                                compact = true,
                            )
                        }
                    }
                    if (showMoreMenu) {
                        InfinityPillMenuButton(contentDescription = "更多操作") { _ -> }
                    }
                }
            }
        }
    }
}

@Preview(name = "1. 错误提示卡片 深色中文 360dp", widthDp = 360, heightDp = 1080, locale = "zh-rCN")
@Composable
fun ErrorCardStatesDarkPhone() = PrototypeFrame(InfinityThemeMode.DARK) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "新版错误指示器 · 黑曜石轻边界 + 可展开脱敏原始诊断",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrototypeErrorCard(
            icon = Icons.Rounded.AccountBalanceWallet,
            title = "提供商余额或额度不足，已停止生成",
            providerSummary = "提供商返回：Insufficient account balance (code 1008)",
            diagnosticChipLabel = "HTTP 402 · 原始错误",
            expanded = true,
            diagnosticMeta = "HTTP 402 · code 1008",
            diagnosticBody = """
                {
                  "base_resp": {
                    "status_code": 1008,
                    "status_msg": "insufficient balance"
                  },
                  "error": {
                    "type": "insufficient_quota",
                    "message": "Your account balance is insufficient."
                  }
                }
            """.trimIndent(),
            secondaryAction = "重试",
            primaryAction = "换模型",
        )
        PrototypeErrorCard(
            icon = Icons.Rounded.CloudOff,
            title = "连接中断，自动重试 10 次后仍未恢复",
            providerSummary = "提供商返回：Unexpected EOF while reading SSE stream",
            diagnosticChipLabel = "原始错误",
            expanded = false,
            secondaryAction = "换模型",
            primaryAction = "重试",
        )
        PrototypeErrorCard(
            icon = Icons.Rounded.ErrorOutline,
            title = "当前请求无法处理，请调整参数或模型",
            providerSummary = "提供商返回：max_tokens: 131072 > 64000",
            diagnosticChipLabel = "HTTP 400 · 原始错误",
            expanded = false,
        )
        PrototypeErrorCard(
            icon = Icons.Rounded.WifiOff,
            title = "内置 Tailscale 未开启，消息未发出",
            secondaryAction = "重试",
            primaryAction = "去开启",
            showMoreMenu = true,
        )
    }
}

@Preview(name = "2. 错误提示卡片 浅色英文 1.3x大字", widthDp = 360, heightDp = 980, locale = "en", fontScale = 1.3f)
@Composable
fun ErrorCardStatesLightLargeFont() = PrototypeFrame(InfinityThemeMode.LIGHT) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Error indicator · Light · 1.3x font scale",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrototypeErrorCard(
            icon = Icons.Rounded.AccountBalanceWallet,
            title = "Provider balance or quota exhausted",
            providerSummary = "Provider: Insufficient account balance (code 1008)",
            diagnosticChipLabel = "HTTP 402 · Raw details",
            expanded = true,
            diagnosticMeta = "HTTP 402 · code 1008",
            diagnosticBody = """
                {
                  "error": {
                    "type": "insufficient_quota",
                    "code": "1008",
                    "message": "Balance is insufficient."
                  }
                }
            """.trimIndent(),
            copyLabel = "Copy",
            secondaryAction = "Retry",
            primaryAction = "Switch model",
        )
        PrototypeErrorCard(
            icon = Icons.Rounded.CloudOff,
            title = "Connection lost after automatic retries",
            providerSummary = "Provider: Peer closed SSE stream unexpectedly",
            diagnosticChipLabel = "Raw details",
            expanded = false,
            secondaryAction = "Switch model",
            primaryAction = "Retry",
        )
    }
}

@Preview(name = "3. 编辑模型 视频类型与云端预设 深色360dp", widthDp = 360, heightDp = 820, locale = "zh-rCN")
@Composable
fun ModelEditorVideoPresetDarkPhone() = PrototypeFrame(InfinityThemeMode.DARK) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            text = "编辑模型 · 火山方舟 ep-20261125-seedance25",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        InfinitySettingsSection(title = "类型") {
            val segments = listOf(
                InfinitySegment("video", "视频", leading = { Icon(Icons.Rounded.Movie, null, Modifier.size(16.dp)) }),
                InfinitySegment("audio", "音频", leading = { Icon(Icons.Rounded.GraphicEq, null, Modifier.size(16.dp)) }),
                InfinitySegment("image", "图像", leading = { Icon(Icons.Rounded.Image, null, Modifier.size(16.dp)) }),
                InfinitySegment("chat", "对话", leading = { Icon(Icons.Rounded.ChatBubbleOutline, null, Modifier.size(16.dp)) }),
                InfinitySegment("reasoning", "推理", leading = { Icon(Icons.Rounded.Psychology, null, Modifier.size(16.dp)) }),
                InfinitySegment("embedding", "嵌入", leading = { Icon(Icons.Rounded.ScatterPlot, null, Modifier.size(16.dp)) }),
                InfinitySegment("rerank", "重排", leading = { Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(16.dp)) }),
            )
            InfinitySegmentedControl(
                segments = segments,
                selected = "video",
                onSelect = {},
                compact = true,
                contentPadding = PaddingValues(horizontal = InfinitySpacing.Small),
                modifier = Modifier.padding(vertical = InfinitySpacing.Small),
            )
        }
        InfinitySettingsSection(
            title = "云端媒体协议",
            footer = "自定义接入点 ID（如方舟 ep-...）无法按名称自动推导时，在此手动绑定视频或语音生成协议；选中视频或音频类型后会自动排除出普通对话模型列表。",
        ) {
            InfinitySettingsItem(
                title = "生成接口预设",
                subtitle = "Seedance 2.5（火山方舟）",
                icon = Icons.Rounded.Movie,
                showChevron = true,
                onClick = {},
            )
            InfinityGroupDivider()
            InfinitySettingsItem(
                title = "媒体设置",
                subtitle = "参考图输入、分辨率与计费",
                icon = Icons.Rounded.Tune,
                showChevron = true,
                onClick = {},
            )
        }
        InfinitySettingsSection(title = "测试") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(start = InfinitySpacing.RowHorizontal, end = InfinitySpacing.Medium, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "媒体模型仅验证提供商连接与配置，不触发实际视频生成",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(InfinitySpacing.Medium))
                FilledTonalButton(onClick = {}, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("验证")
                }
            }
        }
    }
}

@Preview(name = "4. 编辑模型 音频类型 浅色英文 1.3x大字", widthDp = 360, heightDp = 760, locale = "en", fontScale = 1.3f)
@Composable
fun ModelEditorAudioLightLargeFont() = PrototypeFrame(InfinityThemeMode.LIGHT) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    ) {
        InfinitySettingsSection(title = "Type") {
            val segments = listOf(
                InfinitySegment("audio", "Audio", leading = { Icon(Icons.Rounded.GraphicEq, null, Modifier.size(16.dp)) }),
                InfinitySegment("video", "Video", leading = { Icon(Icons.Rounded.Movie, null, Modifier.size(16.dp)) }),
                InfinitySegment("image", "Image", leading = { Icon(Icons.Rounded.Image, null, Modifier.size(16.dp)) }),
                InfinitySegment("chat", "Chat", leading = { Icon(Icons.Rounded.ChatBubbleOutline, null, Modifier.size(16.dp)) }),
            )
            InfinitySegmentedControl(
                segments = segments,
                selected = "audio",
                onSelect = {},
                compact = true,
                contentPadding = PaddingValues(horizontal = InfinitySpacing.Small),
                modifier = Modifier.padding(vertical = InfinitySpacing.Small),
            )
        }
        InfinitySettingsSection(
            title = "Cloud media protocol",
            footer = "Bind a cloud protocol preset when a custom endpoint ID cannot be inferred automatically.",
        ) {
            InfinitySettingsItem(
                title = "Protocol preset",
                subtitle = "MiniMax Speech 2.8 HD",
                icon = Icons.Rounded.GraphicEq,
                showChevron = true,
                onClick = {},
            )
            InfinityGroupDivider()
            InfinitySettingsItem(
                title = "Media settings",
                subtitle = "Audio format & billing tariff",
                icon = Icons.Rounded.Tune,
                showChevron = true,
                onClick = {},
            )
        }
        InfinitySettingsSection(title = "Test") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(start = InfinitySpacing.RowHorizontal, end = InfinitySpacing.Medium, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Provider & media config verified · 184ms",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.infinityDomainColors.success,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(InfinitySpacing.Medium))
                FilledTonalButton(onClick = {}, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Verify")
                }
            }
        }
    }
}

@Preview(name = "5. MiniMax 与智谱 Z.AI 端点变体 深色360dp", widthDp = 360, heightDp = 780, locale = "zh-rCN")
@Composable
fun ProviderPresetsMiniMaxZhipuDarkPhone() = PrototypeFrame(InfinityThemeMode.DARK) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = "提供商预设变体 · 单品牌多区多协议切换",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        InfinitySettingsSection(
            title = "MiniMax Token Plan（订阅计划分组）",
            footer = "官方推荐优先使用 Anthropic 协议；订阅密钥（sk-cp-...）与按量余额密钥（sk-api-...）不可混用。",
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("端点变体", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                InfinitySegmentedControl(
                    segments = listOf(
                        InfinitySegment("cn_anth", "中国 Anthropic"),
                        InfinitySegment("cn_oai", "中国 OpenAI"),
                        InfinitySegment("io_anth", "国际 Anthropic"),
                        InfinitySegment("io_oai", "国际 OpenAI"),
                    ),
                    selected = "cn_anth",
                    onSelect = {},
                    compact = true,
                    contentPadding = PaddingValues(0.dp),
                )
                Text(
                    text = "https://api.minimax.cn/anthropic",
                    style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        InfinitySettingsSection(
            title = "MiniMax（官方提供商分组）",
            footer = "同步支持 api.minimax.cn、api.minimaxi.com 与 api.minimax.io 参数画像及媒体模型。",
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("端点变体", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                InfinitySegmentedControl(
                    segments = listOf(
                        InfinitySegment("cn_oai", "中国 OpenAI"),
                        InfinitySegment("cn_anth", "中国 Anthropic"),
                        InfinitySegment("io_oai", "国际 OpenAI"),
                        InfinitySegment("io_anth", "国际 Anthropic"),
                    ),
                    selected = "cn_oai",
                    onSelect = {},
                    compact = true,
                    contentPadding = PaddingValues(0.dp),
                )
                Text(
                    text = "https://api.minimax.cn/v1",
                    style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        InfinitySettingsSection(
            title = "智谱 Coding Plan（订阅计划分组）",
            footer = "标准「智谱」预设同步补齐中国（open.bigmodel.cn）与国际 Z.AI（api.z.ai）变体。",
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("端点变体", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                InfinitySegmentedControl(
                    segments = listOf(
                        InfinitySegment("cn_oai", "中国 OpenAI"),
                        InfinitySegment("cn_anth", "中国 Anthropic"),
                        InfinitySegment("zai_oai", "国际 OpenAI"),
                        InfinitySegment("zai_anth", "国际 Anthropic"),
                    ),
                    selected = "zai_oai",
                    onSelect = {},
                    compact = true,
                    contentPadding = PaddingValues(0.dp),
                )
                Text(
                    text = "https://api.z.ai/api/coding/paas/v4",
                    style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
