package com.promenar.infinity.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScatterPlot
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySwitch
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.infinityBrandAccentColor
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 提供商管理 / 模型管理 / 编辑模型 / 添加提供商与自定义模型 商业级 UI/UX 重构高保真原型（隔离于 debug 源码）：
 * 1. 全局脚注去“盒中套盒”描边框，回归克制 12sp 排印脚注；
 * 2. 提供商管理：修掉 MiniMax 错显 Anthropic 图标 Bug，4 行压缩为 2 行精炼卡片（常态密钥静默、协议短标 + 完整主机、内网/代理/异常语义微标、右侧启用计数胶囊 + ›）；
 * 3. 模型管理：顶部三层叠罗汉压为两层（无硬描边搜索 + 单行同步工具条 + 动态分类筛选胶囊），同名 ID 去重、能力图标去灰方格、上下文数字并入元数据副行根治 1M 被截断为 1；
 * 4. 编辑模型：顶部身份头卡 + 顶栏一键测速、自适应规格卡（修复上下文冒号、合并重复输入、展示知识截止 + 发布日期）、FlowRow 两行全可见类型选择器（零裁切）、统一蓝标能力胶囊与红图标删除按钮；
 * 5. 添加提供商 + 预设选择弹层 + 添加自定义模型：移除软盘图标、预设弹层补齐搜索与统一品牌底托、自定义模型淘汰原始黑框 OutlinedTextField 并内置规格库实时匹配预览。
 */

@Composable
private fun RedesignProtoFrame(
    mode: InfinityThemeMode,
    content: @Composable () -> Unit,
) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            content()
        }
    }
}

@Composable
private fun RedesignTopBar(
    title: String,
    trailing: @Composable () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Transparent,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

@Composable
private fun RedesignSectionHeader(
    title: String,
    trailingText: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
            color = scheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** 去框化商业级排印脚注：取代原本盒中套盒的 InfinitySectionFooterCard。 */
@Composable
private fun RedesignTypoFootnote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
    )
}

@Composable
private fun RedesignBrandTile(
    @DrawableRes drawableRes: Int?,
    fallbackIcon: ImageVector = Icons.Rounded.Hub,
    accent: Color?,
    size: Dp = 40.dp,
    iconSize: Dp = 21.dp,
    cornerRadius: Dp = 12.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val tint = accent ?: scheme.onSurfaceVariant
    val bg = if (accent != null) accent.copy(alpha = 0.12f) else scheme.surfaceContainerHighest.copy(alpha = 0.85f)
    val rim = if (accent != null) accent.copy(alpha = 0.20f) else scheme.outlineVariant.copy(alpha = 0.35f)
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(bg)
            .border(0.5.dp, rim, RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center,
    ) {
        if (drawableRes != null) {
            Icon(
                painter = painterResource(drawableRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        } else {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(iconSize - 2.dp),
            )
        }
    }
}

@Composable
private fun RedesignMicroBadge(
    label: String,
    containerColor: Color,
    contentColor: Color,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(11.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
            color = contentColor,
            maxLines = 1,
        )
    }
}

// ============================================================================
// Frame 1: 提供商管理列表（2 行极简精炼卡片 + 修复 MiniMax 品牌图标）
// ============================================================================

private data class ProtoProviderRow(
    val name: String,
    val protocolShort: String,
    val host: String,
    val enabledCount: Int,
    val totalCount: Int,
    @param:DrawableRes val iconRes: Int?,
    val brandKey: String?,
    val isTailnet: Boolean = false,
    val isProxy: Boolean = false,
    val missingKey: Boolean = false,
    val fallbackIcon: ImageVector = Icons.Rounded.Hub,
)

@Composable
private fun ProtoProviderListBoard(mode: InfinityThemeMode) {
    RedesignProtoFrame(mode) {
        val scheme = MaterialTheme.colorScheme
        val domain = MaterialTheme.infinityDomainColors
        val rows = listOf(
            ProtoProviderRow("NEWAPI", "OpenAI", "api.promenar.com", 0, 23, null, null, fallbackIcon = Icons.Rounded.Hub),
            ProtoProviderRow("NEWAPI(T)", "OpenAI", "promenar-fnos.ts.net", 6, 16, null, null, isTailnet = true, fallbackIcon = Icons.Rounded.Lan),
            ProtoProviderRow("OpenCode Go", "OpenAI", "opencode.ai", 1, 32, R.drawable.ic_provider_opencode, "opencode"),
            ProtoProviderRow("Gemini", "Gemini", "generativelanguage.googleapis.com", 9, 65, R.drawable.ic_provider_gemini, "google", isProxy = true),
            ProtoProviderRow("智谱 Coding Plan", "OpenAI", "open.bigmodel.cn", 3, 13, R.drawable.ic_provider_zhipu, "zhipu"),
            ProtoProviderRow("MiniMax", "Anthropic", "api.minimax.cn", 1, 9, R.drawable.ic_provider_minimax, "minimax"),
            ProtoProviderRow("DeepSeek 备用", "OpenAI", "api.deepseek.com", 0, 0, R.drawable.ic_provider_deepseek, "deepseek", missingKey = true),
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            RedesignTopBar(
                title = "提供商管理",
                trailing = {
                    FilledTonalButton(
                        onClick = {},
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加", style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp))
                    }
                },
            )
            Text(
                text = "7 个提供商 · 20 个已启用模型",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            Box(Modifier.padding(horizontal = 16.dp)) {
                BettboxListGroup(Modifier.fillMaxWidth()) {
                    rows.forEachIndexed { idx, row ->
                        if (idx > 0) InfinityGroupDivider(startInset = 68.dp)
                        val accent = row.brandKey?.let { infinityBrandAccentColor(it) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RedesignBrandTile(
                                drawableRes = row.iconRes,
                                fallbackIcon = row.fallbackIcon,
                                accent = accent,
                                size = 42.dp,
                                iconSize = 22.dp,
                                cornerRadius = 12.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = row.name,
                                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold),
                                        color = scheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (row.isTailnet) {
                                        RedesignMicroBadge("内网", scheme.secondaryContainer, scheme.onSecondaryContainer, Icons.Rounded.Lan)
                                    }
                                    if (row.isProxy) {
                                        RedesignMicroBadge("代理", scheme.primaryContainer.copy(alpha = 0.7f), scheme.onPrimaryContainer, Icons.Rounded.Shield)
                                    }
                                    if (row.missingKey) {
                                        RedesignMicroBadge("缺密钥", domain.warningContainer, domain.warning, Icons.Rounded.WarningAmber)
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Text(
                                        text = row.protocolShort,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                                        color = scheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(scheme.surfaceContainerHighest.copy(alpha = 0.75f))
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                    )
                                    Text(
                                        text = row.host,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                        color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            val countActive = row.enabledCount > 0
                            Surface(
                                shape = CircleShape,
                                color = if (countActive) scheme.primaryContainer.copy(alpha = 0.72f) else scheme.surfaceContainerHighest.copy(alpha = 0.75f),
                            ) {
                                Text(
                                    text = "${row.enabledCount}/${row.totalCount}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = InfinityMonospace,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 11.5.sp,
                                    ),
                                    color = if (countActive) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier.size(18.dp),
                            )
                            Box(
                                modifier = Modifier.size(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreVert,
                                    contentDescription = null,
                                    tint = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
            RedesignTypoFootnote("点按提供商管理可用模型；密钥正常配置时自动静默，仅缺密钥或停用时提示。")
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ============================================================================
// Frame 2: 模型管理列表（两层精简页头 + 动态分类筛选 + 无方格能力串 + 防 1M 裁切）
// ============================================================================

private data class ProtoModelRow(
    val displayName: String,
    val remoteId: String,
    val typeLabel: String,
    val typeTint: Color,
    val typeBg: Color,
    val contextLabel: String?,
    val icons: List<ImageVector>,
    val extraIconCount: Int = 0,
    val enabled: Boolean,
    @param:DrawableRes val brandIcon: Int?,
    val brandKey: String?,
    val fallbackIcon: ImageVector = Icons.Rounded.ScatterPlot,
)

@Composable
private fun ProtoProviderModelsBoard(mode: InfinityThemeMode) {
    RedesignProtoFrame(mode) {
        val scheme = MaterialTheme.colorScheme
        val reasoningBg = scheme.primaryContainer.copy(alpha = 0.65f)
        val reasoningFg = scheme.onPrimaryContainer
        val speechBg = scheme.tertiaryContainer.copy(alpha = 0.72f)
        val speechFg = scheme.onTertiaryContainer
        val musicBg = scheme.secondaryContainer.copy(alpha = 0.85f)
        val musicFg = scheme.onSecondaryContainer

        val rows = listOf(
            ProtoModelRow(
                "Gemini 3.8 Flash", "gemini-3.8-flash", "推理", reasoningFg, reasoningBg, "1M",
                listOf(Icons.Rounded.Build, Icons.Rounded.Visibility, Icons.Rounded.Mic, Icons.Rounded.Videocam, Icons.Rounded.DataObject),
                extraIconCount = 2, enabled = true, brandIcon = R.drawable.ic_provider_gemini, brandKey = "google",
            ),
            ProtoModelRow(
                "Gemini 4 Hydrogen", "gemini-4-hydrogen", "推理", reasoningFg, reasoningBg, "1M",
                listOf(Icons.Rounded.Build, Icons.Rounded.Visibility, Icons.Rounded.Language, Icons.Rounded.Mic, Icons.Rounded.DataObject),
                extraIconCount = 3, enabled = true, brandIcon = R.drawable.ic_provider_gemini, brandKey = "google",
            ),
            ProtoModelRow(
                "Gemini 3.8 Flash TTS", "gemini-3.8-flash-tts", "语音", speechFg, speechBg, null,
                emptyList(), enabled = true, brandIcon = R.drawable.ic_provider_gemini, brandKey = "google",
            ),
            ProtoModelRow(
                "Lyria 3.5", "lyria-3.5", "音乐", musicFg, musicBg, "128K",
                listOf(Icons.Rounded.Visibility), enabled = true, brandIcon = R.drawable.ic_provider_google, brandKey = "google",
            ),
            ProtoModelRow(
                "MiniMax-M3", "MiniMax-M3", "推理", reasoningFg, reasoningBg, "1M",
                listOf(Icons.Rounded.Build, Icons.Rounded.Visibility, Icons.Rounded.Videocam, Icons.Rounded.DataObject, Icons.Rounded.Cached),
                enabled = true, brandIcon = R.drawable.ic_provider_minimax, brandKey = "minimax",
            ),
            ProtoModelRow(
                "BGE M3", "BAAI/bge-m3", "嵌入", scheme.onSecondaryContainer, scheme.secondaryContainer.copy(alpha = 0.7f), "8K",
                emptyList(), enabled = false, brandIcon = null, brandKey = null, fallbackIcon = Icons.Rounded.ScatterPlot,
            ),
        )
        val filters = listOf("全部 65" to true, "已启用 9" to false, "推理 29" to false, "语音 5" to false, "音乐 3" to false, "视频 1" to false)

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            RedesignTopBar(
                title = "Gemini",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(22.dp))
                        }
                        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(20.dp))
                        }
                    }
                },
            )

            // 层 1：无硬描边搜索框 + 紧凑单行状态同步条
            Surface(
                shape = CircleShape,
                color = scheme.surfaceContainerHigh.copy(alpha = 0.78f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(42.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text("搜索模型名或 ID…", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant.copy(alpha = 0.75f))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "已启用 9 / 65 · 规格库 11-26 版",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = scheme.onSurface,
                    )
                    Text(
                        text = "4 天前从提供商同步",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = scheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(
                    onClick = {},
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("同步", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium))
                }
            }

            // 层 2：紧凑分类筛选胶囊栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                filters.take(5).forEach { (label, selected) ->
                    Surface(
                        shape = CircleShape,
                        color = if (selected) scheme.primary else scheme.surfaceContainerHigh.copy(alpha = 0.72f),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                            maxLines = 1,
                        )
                    }
                }
            }

            // 模型分组卡
            Box(Modifier.padding(horizontal = 16.dp)) {
                BettboxListGroup(Modifier.fillMaxWidth()) {
                    rows.forEachIndexed { idx, row ->
                        if (idx > 0) InfinityGroupDivider(startInset = 64.dp)
                        val accent = row.brandKey?.let { infinityBrandAccentColor(it) }
                        val showIdLine = !row.displayName.equals(row.remoteId, ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RedesignBrandTile(
                                drawableRes = row.brandIcon,
                                fallbackIcon = row.fallbackIcon,
                                accent = accent,
                                size = 38.dp,
                                iconSize = 20.dp,
                                cornerRadius = 11.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    text = row.displayName,
                                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                                    color = scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (showIdLine) {
                                    Text(
                                        text = row.remoteId,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace, fontSize = 11.5.sp),
                                        color = scheme.onSurfaceVariant.copy(alpha = 0.72f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                // 统一元数据副行：类型微标 + 上下文紧跟 + 无框单色能力图标（彻底杜绝 1M 挤出裁切）
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    RedesignMicroBadge(row.typeLabel, row.typeBg, row.typeTint)
                                    if (row.contextLabel != null) {
                                        Text(
                                            text = row.contextLabel,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = InfinityMonospace,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 11.5.sp,
                                            ),
                                            color = scheme.onSurfaceVariant,
                                        )
                                    }
                                    if (row.icons.isNotEmpty()) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            row.icons.forEach { ic ->
                                                Icon(
                                                    imageVector = ic,
                                                    contentDescription = null,
                                                    tint = scheme.onSurfaceVariant.copy(alpha = 0.72f),
                                                    modifier = Modifier.size(13.5.dp),
                                                )
                                            }
                                            if (row.extraIconCount > 0) {
                                                Text(
                                                    text = "+${row.extraIconCount}",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                                    color = scheme.onSurfaceVariant.copy(alpha = 0.72f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            InfinitySwitch(checked = row.enabled, onCheckedChange = {})
                        }
                    }
                }
            }
            RedesignTypoFootnote("点按任意模型行可编辑规格、能力与测速；支持按类型或已启用状态快速筛选。")
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ============================================================================
// Frame 3: 编辑模型（身份头卡 + 顶栏测速 + 自适应规格卡 + FlowRow 零裁切类型 + 统一能力胶囊）
// ============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProtoModelEditorBoard(mode: InfinityThemeMode) {
    RedesignProtoFrame(mode) {
        val scheme = MaterialTheme.colorScheme
        val domain = MaterialTheme.infinityDomainColors
        val types = listOf(
            Triple("对话", Icons.Rounded.ChatBubbleOutline, false),
            Triple("推理", Icons.Rounded.Psychology, true),
            Triple("图像", Icons.Rounded.Image, false),
            Triple("视频", Icons.Rounded.Movie, false),
            Triple("语音", Icons.Rounded.RecordVoiceOver, false),
            Triple("音乐", Icons.Rounded.MusicNote, false),
            Triple("实时", Icons.Rounded.GraphicEq, false),
            Triple("嵌入", Icons.Rounded.ScatterPlot, false),
            Triple("重排", Icons.AutoMirrored.Rounded.Sort, false),
        )
        val caps = listOf(
            "工具调用" to Icons.Rounded.Build,
            "视觉" to Icons.Rounded.Visibility,
            "音频输入" to Icons.Rounded.Mic,
            "视频理解" to Icons.Rounded.Videocam,
            "JSON" to Icons.Rounded.DataObject,
            "提示词缓存" to Icons.Rounded.Cached,
            "联网搜索" to Icons.Rounded.Language,
        )

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            RedesignTopBar(
                title = "编辑模型",
                trailing = {
                    FilledTonalButton(
                        onClick = {},
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("测试", style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp))
                    }
                },
            )

            // 顶部身份锚点 + 基本信息合并卡
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                BettboxListGroup(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RedesignBrandTile(
                            drawableRes = R.drawable.ic_provider_gemini,
                            accent = infinityBrandAccentColor("google"),
                            size = 44.dp,
                            iconSize = 24.dp,
                            cornerRadius = 13.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Gemini 3.8 Flash",
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold),
                                color = scheme.onSurface,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "google/gemini-3.8-flash",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace, fontSize = 11.5.sp),
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Icon(
                                    Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    tint = scheme.onSurfaceVariant.copy(alpha = 0.65f),
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                        InfinitySwitch(checked = true, onCheckedChange = {})
                    }
                    InfinityGroupDivider(startInset = 16.dp)
                    ProtoFieldDisplayRow(label = "显示名称", value = "Gemini 3.8 Flash")
                    InfinityGroupDivider(startInset = 16.dp)
                    ProtoFieldDisplayRow(label = "模型 ID", value = "gemini-3.8-flash", monospace = true)
                }
            }

            // 自适应规格卡：对称两栏可编辑上限（去重复输入、去冒号）+ 单行时间线（知识截止 + 发布日期）
            RedesignSectionHeader("规格", trailingText = "修改自动保存")
            Box(Modifier.padding(horizontal = 16.dp)) {
                BettboxListGroup(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        ProtoEditableKpiCell(
                            label = "上下文窗口",
                            shortValue = "1M",
                            exactValue = "1,048,576",
                            source = "规格库",
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier
                                .width(0.5.dp)
                                .fillMaxHeight()
                                .background(scheme.outlineVariant.copy(alpha = 0.45f)),
                        )
                        ProtoEditableKpiCell(
                            label = "输出上限",
                            shortValue = "64K",
                            exactValue = "65,536",
                            source = "提供商",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    InfinityGroupDivider(startInset = 0.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Rounded.History, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                            Text("知识截止", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            Text("2026-03", style = MaterialTheme.typography.labelLarge.copy(fontFamily = InfinityMonospace, fontSize = 12.5.sp), color = scheme.onSurface)
                        }
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Rounded.CalendarMonth, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                            Text("发布", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            Text("2026-09-02", style = MaterialTheme.typography.labelLarge.copy(fontFamily = InfinityMonospace, fontSize = 12.5.sp), color = scheme.onSurface)
                        }
                    }
                }
            }
            RedesignTypoFootnote("点按上下文或输出上限可手动覆盖，优先于规格库；输入上限与上下文一致时自动合并。")

            // 零裁切两行 FlowRow 类型选择（5 + 4 均衡排版）
            RedesignSectionHeader("类型")
            Box(Modifier.padding(horizontal = 16.dp)) {
                BettboxListGroup(Modifier.fillMaxWidth()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        types.forEach { (label, icon, selected) ->
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = if (selected) scheme.primaryContainer else scheme.surfaceContainerHighest.copy(alpha = 0.55f),
                                border = if (selected) BorderStroke(1.dp, scheme.primary.copy(alpha = 0.45f)) else null,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontSize = 12.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        ),
                                        color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 统一蓝标分组头 + 可视化能力胶囊
            RedesignSectionHeader("能力", trailingText = "已选 7 项 · 点按调整")
            Box(Modifier.padding(horizontal = 16.dp)) {
                BettboxListGroup(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FlowRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            caps.forEach { (label, icon) ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = scheme.surfaceContainerHighest.copy(alpha = 0.65f),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
                                        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp), color = scheme.onSurface)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // 会话请求头
            RedesignSectionHeader("会话请求头")
            Box(Modifier.padding(horizontal = 16.dp)) {
                BettboxListGroup(Modifier.fillMaxWidth()) {
                    InfinitySegmentedControl(
                        segments = listOf(
                            InfinitySegment("inherit", "跟随提供商"),
                            InfinitySegment("on", "开启"),
                            InfinitySegment("off", "关闭"),
                        ),
                        selected = "inherit",
                        onSelect = {},
                        fillWidth = true,
                        compact = true,
                        contentPadding = PaddingValues(10.dp),
                    )
                }
            }
            RedesignTypoFootnote("仅随会话请求发送，用于命中上游提示词缓存。")

            // 统一语义色危险操作
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = scheme.errorContainer.copy(alpha = 0.42f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = scheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("删除模型", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium), color = scheme.error)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProtoFieldDisplayRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    placeholder: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = if (monospace) InfinityMonospace else MaterialTheme.typography.bodyLarge.fontFamily,
                    fontSize = if (monospace) 14.sp else 15.5.sp,
                ),
                color = if (placeholder) scheme.onSurfaceVariant.copy(alpha = 0.55f) else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun ProtoEditableKpiCell(
    label: String,
    shortValue: String,
    exactValue: String,
    source: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Edit, contentDescription = null, tint = scheme.primary.copy(alpha = 0.75f), modifier = Modifier.size(13.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = shortValue,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
                color = scheme.onSurface,
            )
            Surface(shape = RoundedCornerShape(5.dp), color = scheme.surfaceContainerHighest.copy(alpha = 0.85f)) {
                Text(
                    text = source,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            text = exactValue,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace, fontSize = 11.5.sp),
            color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}

// ============================================================================
// Frame 4: 添加提供商 + 预设弹层 + 添加自定义模型（统一表单语言 + 实时规格库预览）
// ============================================================================

@Composable
private fun ProtoAddProviderBoard(mode: InfinityThemeMode) {
    RedesignProtoFrame(mode) {
        val scheme = MaterialTheme.colorScheme
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                RedesignTopBar("添加提供商")

                RedesignSectionHeader("提供商")
                Box(Modifier.padding(horizontal = 16.dp)) {
                    BettboxListGroup(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RedesignBrandTile(
                                drawableRes = R.drawable.ic_provider_minimax,
                                accent = infinityBrandAccentColor("minimax"),
                                size = 34.dp,
                                iconSize = 18.dp,
                                cornerRadius = 10.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("预设提供商", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, modifier = Modifier.weight(1f))
                            Text("MiniMax Token Plan", style = MaterialTheme.typography.bodyMedium, color = scheme.primary)
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                        InfinityGroupDivider(startInset = 16.dp)
                        ProtoFieldDisplayRow(label = "名称", value = "MiniMax")
                    }
                }

                RedesignSectionHeader("接口与通道")
                Box(Modifier.padding(horizontal = 16.dp)) {
                    BettboxListGroup(Modifier.fillMaxWidth()) {
                        InfinitySegmentedControl(
                            segments = listOf(
                                InfinitySegment("public", "公网", leading = { Icon(Icons.Rounded.Public, null, Modifier.size(15.dp)) }),
                                InfinitySegment("tailnet", "Tailscale 内网", leading = { Icon(Icons.Rounded.Lan, null, Modifier.size(15.dp)) }),
                            ),
                            selected = "public",
                            onSelect = {},
                            fillWidth = true,
                            compact = true,
                            contentPadding = PaddingValues(10.dp),
                        )
                        InfinityGroupDivider(startInset = 16.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("经内置代理连接", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                                Text("国内直连服务建议关闭；海外服务可一键启用", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = scheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(10.dp))
                            InfinitySwitch(checked = false, onCheckedChange = {})
                        }
                        InfinityGroupDivider(startInset = 16.dp)
                        ProtoFieldDisplayRow(label = "Base URL", value = "https://api.minimax.cn/anthropic", monospace = true)
                    }
                }

                RedesignSectionHeader("凭证")
                Box(Modifier.padding(horizontal = 16.dp)) {
                    BettboxListGroup(Modifier.fillMaxWidth()) {
                        ProtoFieldDisplayRow(
                            label = "API Key",
                            value = "sk-cp-••••••••••••••",
                            monospace = true,
                            trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                                    Icon(Icons.Rounded.VisibilityOff, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                }
                            },
                        )
                    }
                }
                RedesignTypoFootnote("密钥使用 Android Keystore 加密存储于本机，不上传云端。")

                RedesignSectionHeader("高级")
                Box(Modifier.padding(horizontal = 16.dp)) {
                    BettboxListGroup(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("会话请求头", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                                Text("附带会话 ID 供上游复用提示词缓存", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = scheme.onSurfaceVariant)
                            }
                            InfinitySwitch(checked = true, onCheckedChange = {})
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 现代双层主次底栏（移除 90 年代软盘图标）
            HorizontalDivider(thickness = 0.5.dp, color = scheme.outlineVariant.copy(alpha = 0.45f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.background)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {},
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(0.42f).height(46.dp),
                ) {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("测试连接", style = MaterialTheme.typography.labelLarge)
                }
                Button(
                    onClick = {},
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(0.58f).height(46.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("保存提供商", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

@Composable
private fun ProtoSheetsBoard(mode: InfinityThemeMode) {
    RedesignProtoFrame(mode) {
        val scheme = MaterialTheme.colorScheme
        val presets = listOf(
            Triple("MiniMax Token Plan", "api.minimax.cn · 4 个区域/协议", R.drawable.ic_provider_minimax to "minimax"),
            Triple("智谱 Coding Plan", "open.bigmodel.cn · 中国/国际", R.drawable.ic_provider_zhipu to "zhipu"),
            Triple("OpenCode Go", "opencode.ai/zen/go · OpenAI/Anthropic", R.drawable.ic_provider_opencode to "opencode"),
            Triple("Google Gemini", "generativelanguage.googleapis.com", R.drawable.ic_provider_gemini to "google"),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 弹层 1：升级版预设提供商选择器
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = scheme.surfaceContainerLowest,
                border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(34.dp).height(4.dp).clip(CircleShape).background(scheme.outlineVariant))
                    }
                    Text(
                        "预设提供商",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                    Surface(
                        shape = CircleShape,
                        color = scheme.surfaceContainerHigh.copy(alpha = 0.78f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(38.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Text("搜索预设或厂商名…", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant.copy(alpha = 0.75f))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.padding(horizontal = 12.dp)) {
                        BettboxListGroup(Modifier.fillMaxWidth()) {
                            presets.forEachIndexed { idx, (name, sub, brand) ->
                                if (idx > 0) InfinityGroupDivider(startInset = 60.dp)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RedesignBrandTile(
                                        drawableRes = brand.first,
                                        accent = infinityBrandAccentColor(brand.second),
                                        size = 36.dp,
                                        iconSize = 19.dp,
                                        cornerRadius = 10.dp,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = scheme.onSurface)
                                        Text(sub, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (idx == 0) {
                                        Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 弹层 2：重构版添加自定义模型（淘汰原始黑框 OutlinedTextField + 规格库实时命中预览）
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = scheme.surfaceContainerLowest,
                border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(34.dp).height(4.dp).clip(CircleShape).background(scheme.outlineVariant))
                    }
                    Text(
                        "添加自定义模型",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                    Box(Modifier.padding(horizontal = 14.dp)) {
                        BettboxListGroup(Modifier.fillMaxWidth()) {
                            ProtoFieldDisplayRow(label = "模型 ID", value = "deepseek-v4.1-flash", monospace = true)
                            InfinityGroupDivider(startInset = 16.dp)
                            ProtoFieldDisplayRow(label = "显示名称（选填）", value = "自动使用 DeepSeek V4.1 Flash", placeholder = true)
                        }
                    }
                    // 规格库实时命中预览条
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = scheme.primaryContainer.copy(alpha = 0.36f),
                        border = BorderStroke(0.5.dp, scheme.primary.copy(alpha = 0.25f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RedesignBrandTile(
                                drawableRes = R.drawable.ic_provider_deepseek,
                                accent = infinityBrandAccentColor("deepseek"),
                                size = 32.dp,
                                iconSize = 18.dp,
                                cornerRadius = 9.dp,
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "已匹配规格库 · DeepSeek V4.1 Flash",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = scheme.onSurface,
                                )
                                Text(
                                    "推理 · 1M 上下文 · 384K 输出 · 截止 2025-05",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Button(
                        onClick = {},
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .height(44.dp),
                    ) {
                        Text("添加模型", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
    }
}

@Preview(name = "1_ProviderList_Light", widthDp = 390, heightDp = 820, showBackground = true)
@Composable
fun ProviderListRedesignLightPreview() {
    ProtoProviderListBoard(InfinityThemeMode.LIGHT)
}

@Preview(name = "1_ProviderList_Dark", widthDp = 390, heightDp = 820, showBackground = true)
@Composable
fun ProviderListRedesignDarkPreview() {
    ProtoProviderListBoard(InfinityThemeMode.DARK)
}

@Preview(name = "2_ProviderModels_Light", widthDp = 390, heightDp = 820, showBackground = true)
@Composable
fun ProviderModelsRedesignLightPreview() {
    ProtoProviderModelsBoard(InfinityThemeMode.LIGHT)
}

@Preview(name = "2_ProviderModels_Dark", widthDp = 390, heightDp = 820, showBackground = true)
@Composable
fun ProviderModelsRedesignDarkPreview() {
    ProtoProviderModelsBoard(InfinityThemeMode.DARK)
}

@Preview(name = "3_ModelEditor_Light", widthDp = 390, heightDp = 980, showBackground = true)
@Composable
fun ModelEditorRedesignLightPreview() {
    ProtoModelEditorBoard(InfinityThemeMode.LIGHT)
}

@Preview(name = "3_ModelEditor_Dark", widthDp = 390, heightDp = 980, showBackground = true)
@Composable
fun ModelEditorRedesignDarkPreview() {
    ProtoModelEditorBoard(InfinityThemeMode.DARK)
}

@Preview(name = "4_AddProvider_Light", widthDp = 390, heightDp = 820, showBackground = true)
@Composable
fun AddProviderRedesignLightPreview() {
    ProtoAddProviderBoard(InfinityThemeMode.LIGHT)
}

@Preview(name = "5_Sheets_Light", widthDp = 390, heightDp = 860, showBackground = true)
@Composable
fun ProviderSheetsRedesignLightPreview() {
    ProtoSheetsBoard(InfinityThemeMode.LIGHT)
}
