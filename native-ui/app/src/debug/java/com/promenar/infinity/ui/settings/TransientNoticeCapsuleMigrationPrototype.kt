package com.promenar.infinity.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityBackButton
import com.promenar.infinity.ui.common.InfinityHeaderTitle
import com.promenar.infinity.ui.common.InfinityNoticeSeverity
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinitySearchBar
import com.promenar.infinity.ui.common.ModelBrandIcon
import com.promenar.infinity.ui.common.ModelBrandResolver
import com.promenar.infinity.ui.common.ModelBrandTile
import com.promenar.infinity.ui.common.brandAccentColor
import com.promenar.infinity.ui.common.computeNoticeHorizontalPlacement
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityObsidianGlassTint
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.keepCjkTailTogether

/*
 * 隔离无状态视觉原型 v1：方案 A — 五处一次性内联通知迁移至全站顶部统一悬浮胶囊（InfinityNoticeHost）。
 * 验证零高度侵入、顶栏避让、窄屏大字号两行折行、以及折叠屏设置双栏右栏水平居中效果。
 */

/** 对齐 InfinityNoticeHost 正式熏黑微光 / 冷白磨砂顶部悬浮胶囊像素规范。 */
@Composable
private fun PrototypeNoticeCapsuleOverlay(
    severity: InfinityNoticeSeverity,
    message: String,
    topOffset: Dp = 72.dp,
    startInset: Dp = 0.dp,
    endInset: Dp = 0.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val dark = domain.isDark
    val accent = when (severity) {
        InfinityNoticeSeverity.SUCCESS -> domain.success
        InfinityNoticeSeverity.INFO -> scheme.primary
        InfinityNoticeSeverity.WARNING -> domain.warning
        InfinityNoticeSeverity.ERROR -> domain.ragError
    }
    val icon: ImageVector = when (severity) {
        InfinityNoticeSeverity.SUCCESS -> Icons.Rounded.CheckCircle
        InfinityNoticeSeverity.INFO -> Icons.Rounded.Info
        InfinityNoticeSeverity.WARNING -> Icons.Rounded.WarningAmber
        InfinityNoticeSeverity.ERROR -> Icons.Rounded.ErrorOutline
    }
    val containerColor = if (dark) InfinityObsidianGlassTint else InfinityPureWhite
    val borderColor = if (dark) InfinityPureWhite.copy(alpha = 0.16f) else scheme.outlineVariant.copy(alpha = 0.58f)
    val topHighlight = if (dark) InfinityPureWhite.copy(alpha = 0.14f) else InfinityPureWhite.copy(alpha = 0.92f)
    val shape = RoundedCornerShape(24.dp)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val placement = computeNoticeHorizontalPlacement(
            maxWidth = maxWidth,
            startInset = startInset,
            rawEndInset = endInset,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = topOffset,
                    start = placement.startInset + 16.dp,
                    end = placement.endInset + 16.dp,
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            val minWidth = 180.dp.coerceAtMost(placement.maxCapsuleWidth)
            Surface(
                modifier = Modifier.widthIn(min = minWidth, max = placement.maxCapsuleWidth),
                shape = shape,
                color = containerColor,
                contentColor = scheme.onSurface,
                border = BorderStroke(1.dp, borderColor),
                shadowElevation = 12.dp,
            ) {
                Box(
                    Modifier
                        .clip(shape)
                        .drawWithCache {
                            val hiPx = 18.dp.toPx().coerceAtMost(size.height)
                            val hiBrush = Brush.verticalGradient(
                                0f to topHighlight,
                                1f to Color.Transparent,
                                startY = 0f,
                                endY = hiPx,
                            )
                            val center = Offset(26.dp.toPx().coerceAtMost(size.width * 0.25f), size.height / 2f)
                            val radius = maxOf(size.height * 2.2f, 110.dp.toPx())
                            val glowBrush = Brush.radialGradient(
                                0f to accent.copy(alpha = if (dark) 0.20f else 0.11f),
                                0.58f to accent.copy(alpha = if (dark) 0.06f else 0.03f),
                                1f to Color.Transparent,
                                center = center,
                                radius = radius,
                            )
                            onDrawBehind {
                                drawRect(brush = glowBrush)
                                drawRect(brush = hiBrush, size = Size(size.width, hiPx))
                            }
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = if (dark) 0.18f else 0.12f))
                                .border(0.5.dp, accent.copy(alpha = if (dark) 0.30f else 0.24f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                        }
                        Text(
                            text = message.keepCjkTailTogether(),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.5.sp,
                                lineHeight = 18.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = scheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoProviderSquircle(name: String, baseUrl: String) {
    val scheme = MaterialTheme.colorScheme
    val brand = remember(name, baseUrl) { ModelBrandResolver.brandKey(name, baseUrl) }
    val accent = brandAccentColor(brand)
    val bg = accent?.copy(alpha = 0.14f) ?: scheme.surfaceContainerHighest
    val rim = accent?.copy(alpha = 0.24f) ?: scheme.outlineVariant.copy(alpha = 0.40f)
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(0.5.dp, rim, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        ModelBrandIcon(
            modelId = name,
            modifier = Modifier.size(16.dp),
            contentDescription = null,
            fallbackTint = accent ?: scheme.onSurfaceVariant,
            brandOverride = brand,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtoProviderModelsSurface(showBack: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { if (showBack) InfinityBackButton(onClick = {}) },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ProtoProviderSquircle("OpenRouter", "https://openrouter.ai/api/v1")
                        InfinityHeaderTitle(text = "OpenRouter", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    IconButton(onClick = {}) { Icon(Icons.Rounded.Add, contentDescription = null) }
                    IconButton(onClick = {}) { Icon(Icons.Rounded.MoreVert, contentDescription = null) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = scheme.onBackground,
                    navigationIconContentColor = scheme.onSurfaceVariant,
                    actionIconContentColor = scheme.onSurfaceVariant,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InfinitySearchBar(value = "", onValueChange = {}, placeholder = "搜索模型…", borderless = true)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("已启用 8 / 441 · 规格库 11-26 版", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                    Text("刚刚同步", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = {}, shape = CircleShape, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("同步", maxLines = 1)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProtoFilterChip("全部 441", selected = true)
                ProtoFilterChip("推理 334", selected = false)
                ProtoFilterChip("对话 107", selected = false)
            }
            // 模型列表紧贴筛选项，完全不被内联绿色通知卡片向下顶开
            BettboxListGroup {
                ProtoModelItemRow("Claude Sonnet 4.6", "anthropic/claude-sonnet-4.6", enabled = true)
                HorizontalDivider(thickness = 0.5.dp, color = scheme.outlineVariant.copy(alpha = 0.45f))
                ProtoModelItemRow("Gemini 2.5 Pro", "google/gemini-2.5-pro", enabled = true)
                HorizontalDivider(thickness = 0.5.dp, color = scheme.outlineVariant.copy(alpha = 0.45f))
                ProtoModelItemRow("DeepSeek R1", "deepseek/deepseek-r1", enabled = false)
            }
        }
    }
}

@Composable
private fun ProtoFilterChip(label: String, selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun ProtoModelItemRow(title: String, remoteId: String, enabled: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModelBrandTile(modelId = remoteId, size = 34.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(remoteId, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = enabled, onCheckedChange = {})
    }
}

@Preview(name = "1_ModelsSync_Dark_390", widthDp = 390, heightDp = 520, showBackground = true)
@Composable
fun NoticeCapsuleProviderModelsDarkPreview() {
    InfinityTheme(
        preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY),
    ) {
        Box(Modifier.fillMaxSize()) {
            ProtoProviderModelsSurface(showBack = true)
            PrototypeNoticeCapsuleOverlay(
                severity = InfinityNoticeSeverity.SUCCESS,
                message = "同步完成：新增 441 个，更新 0 个",
                topOffset = 72.dp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "2_ProviderForm_Error_Light_390", widthDp = 390, heightDp = 520, showBackground = true)
@Composable
fun NoticeCapsuleProviderFormLightPreview() {
    InfinityTheme(
        preferences = InfinityThemePreferences(mode = InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY),
    ) {
        val scheme = MaterialTheme.colorScheme
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = scheme.background,
                topBar = {
                    TopAppBar(
                        navigationIcon = { InfinityBackButton(onClick = {}) },
                        title = { InfinityHeaderTitle("编辑提供商", style = MaterialTheme.typography.titleLarge) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    )
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = "OpenRouter",
                            onValueChange = {},
                            label = { Text("显示名称") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = "https://openrouter.ai/api/v1",
                            onValueChange = {},
                            label = { Text("接口地址") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = "sk-or-v1-••••••••••••••",
                            onValueChange = {},
                            label = { Text("API Key") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                    // 固定高度底栏：不再被测试结果状态条向上顶高
                    HorizontalDivider(thickness = 0.5.dp, color = scheme.outlineVariant.copy(alpha = 0.45f))
                    Box(Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = 12.dp)) {
                        InfinityPillRow(modifier = Modifier.fillMaxWidth(), spacing = 10.dp) {
                            FilledTonalButton(
                                onClick = {},
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("测试", maxLines = 1)
                            }
                            Button(
                                onClick = {},
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("保存", maxLines = 1)
                            }
                        }
                    }
                }
            }
            PrototypeNoticeCapsuleOverlay(
                severity = InfinityNoticeSeverity.ERROR,
                message = "失败 (HTTP 401)：凭证被拒绝，请检查 API Key 是否有效",
                topOffset = 72.dp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "3_ModelEditor_Narrow_130Font", widthDp = 360, heightDp = 500, fontScale = 1.3f, showBackground = true)
@Composable
fun NoticeCapsuleModelEditorLargeFontPreview() {
    InfinityTheme(
        preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY),
    ) {
        val scheme = MaterialTheme.colorScheme
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = scheme.background,
                topBar = {
                    TopAppBar(
                        navigationIcon = { InfinityBackButton(onClick = {}) },
                        title = { InfinityHeaderTitle("模型配置", style = MaterialTheme.typography.titleLarge) },
                        actions = {
                            FilledTonalButton(
                                onClick = {},
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.padding(end = 8.dp).height(34.dp),
                            ) {
                                Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("测试", style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), maxLines = 1)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BettboxListGroup {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ModelBrandTile(modelId = "anthropic/claude-sonnet-4.6", size = 40.dp)
                            Column(Modifier.weight(1f)) {
                                Text("Claude Sonnet 4.6", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("anthropic/claude-sonnet-4.6", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    BettboxListGroup {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("上下文窗口", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                            Text("200,000 tokens", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                        }
                    }
                }
            }
            PrototypeNoticeCapsuleOverlay(
                severity = InfinityNoticeSeverity.SUCCESS,
                message = "测试通过 · 312ms",
                topOffset = 72.dp,
            )
        }
    }
}

@Preview(name = "4_SettingsTwoPane_Foldable_840", widthDp = 840, heightDp = 500, showBackground = true)
@Composable
fun NoticeCapsuleSettingsTwoPaneFoldablePreview() {
    InfinityTheme(
        preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY),
    ) {
        val scheme = MaterialTheme.colorScheme
        Box(Modifier.fillMaxSize().background(scheme.background)) {
            Row(Modifier.fillMaxSize()) {
                // 左侧 80dp 主导航轨
                Column(
                    modifier = Modifier.width(80.dp).fillMaxHeight().background(scheme.surface).padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Icon(Icons.Rounded.Tune, contentDescription = null, tint = scheme.onSurfaceVariant)
                    Box(
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(scheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Settings, contentDescription = null, tint = scheme.onSecondaryContainer)
                    }
                }
                // 左列 320dp 设置目录
                Column(
                    modifier = Modifier.width(320.dp).fillMaxHeight().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("设置", style = MaterialTheme.typography.titleLarge, color = scheme.onBackground, modifier = Modifier.padding(vertical = 8.dp))
                    Surface(shape = RoundedCornerShape(14.dp), color = scheme.secondaryContainer.copy(alpha = 0.65f)) {
                        Text("模型提供商", style = MaterialTheme.typography.titleSmall, color = scheme.onSecondaryContainer, modifier = Modifier.fillMaxWidth().padding(14.dp))
                    }
                    Surface(shape = RoundedCornerShape(14.dp), color = scheme.surfaceContainerLow) {
                        Text("默认模型", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, modifier = Modifier.fillMaxWidth().padding(14.dp))
                    }
                    Surface(shape = RoundedCornerShape(14.dp), color = scheme.surfaceContainerLow) {
                        Text("界面与壁纸", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, modifier = Modifier.fillMaxWidth().padding(14.dp))
                    }
                }
                VerticalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                // 右列 440dp 详情页
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    ProtoProviderModelsSurface(showBack = true)
                }
            }
            // 通过 SettingsTwoPane 右列水平避让（startInset = 400dp），悬浮胶囊精确居中于右栏详情页正上方
            PrototypeNoticeCapsuleOverlay(
                severity = InfinityNoticeSeverity.SUCCESS,
                message = "同步完成：新增 441 个，更新 0 个",
                topOffset = 72.dp,
                startInset = 400.dp,
            )
        }
    }
}
