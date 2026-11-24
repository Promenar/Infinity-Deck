package com.promenar.infinity.ui.settings

import android.annotation.SuppressLint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.color.utilities.Hct
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.common.obsidianTopGlint
import com.promenar.infinity.ui.production.ProductionStatusChip
import com.promenar.infinity.ui.production.ProductionTone
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityIceBlueSeed
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityObsidianDialogChip
import com.promenar.infinity.ui.theme.InfinityObsidianSolidContainer
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.InfinitySchemeStyle
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.seededColorScheme
import com.promenar.infinity.ui.theme.withPureBlackSurface

/*
 * 两批高保真 UI 原型（v1，仅存于 debug 源码，待用户确认后分两批实施）：
 *
 * 第一批：外观「明亮（BRIGHT）」配色风格 + 首页「对话」与「资料」页头统一 `34dp` 圆形主色 `+` 按钮
 * - 解决深色模式下 M3 原生 4 套方案主色固定在 Tone 80 发淡发粉或色相偏移的痛点：
 *   「明亮」严格锁定种子原色相（零偏色），将深色主强调色与容器色推到高饱和通透的中亮区间，
 *   并按实际亮度自适应高对比度前景（满足 WCAG AA），浅色模式同步保持清透高饱和。
 * - 对话页与资料页页头主动作统一为 `34dp` 圆形 `primary / onPrimary` 按钮，视觉与触控语义完全一致。
 *
 * 第二批：内置代理（裁剪版 mihomo 独立 Go 子进程，Sideload & Play 双渠道）
 * - 设置 → 执行环境与连接 → 内置代理：总开关、运行状态与延迟测速、出站模式（单节点 / 顺序降级 fallback）、
 *   订阅链接与节点分享链接导入、节点启停与顺序降级排序、高级模式内置 YAML 编辑器（带行号与内核校验）、
 *   清晰覆盖范围说明（AI 提供商按需勾选、浏览器与 Agent 公网自动生效、Linux 通过 $INFINITY_PROXY_* 按需使用、
 *   Tailscale 与局域网始终直连）。
 * - 提供商编辑页「接口」分组：公网分支下新增「经内置代理连接」开关（默认关闭即直连，开启后显示当前出口节点或未开启提醒）。
 */

private fun brightColorScheme(seed: Color, dark: Boolean, pureBlack: Boolean = dark): ColorScheme =
    seededColorScheme(seed, dark, InfinitySchemeStyle.BRIGHT).withPureBlackSurface(pureBlack && dark)

@Composable
private fun BrightProtoTheme(
    dark: Boolean = true,
    seed: Color = InfinityIceBlueSeed,
    content: @Composable () -> Unit,
) {
    InfinityTheme(
        darkTheme = dark,
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
            primaryColor = seed.value.toLong(),
            schemeStyle = InfinitySchemeStyle.FIDELITY,
            pureBlack = dark,
        ),
    ) {
        MaterialTheme(
            colorScheme = brightColorScheme(seed, dark, pureBlack = dark),
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}

/** 统一页头 34dp 圆形主色 + 按钮（对话页与资料页完全一致）。 */
@Composable
private fun UnifiedHeaderCircleAddButton(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(48.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ProtoNavTopBar(
    title: String,
    showBack: Boolean = true,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = if (showBack) 8.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
private fun SchemeSwatchComparisonCard(dark: Boolean) {
    val seeds = listOf(
        "冰蓝" to InfinityIceBlueSeed,
        "玫瑰" to Color(0xFFF43F5E),
        "翡翠" to Color(0xFF10B981),
        "琥珀" to Color(0xFFF59E0B),
    )
    val styles: List<Pair<String, (Color) -> ColorScheme>> = listOf(
        "柔和" to { c -> seededColorScheme(c, dark, InfinitySchemeStyle.TONAL_SPOT) },
        "鲜艳" to { c -> seededColorScheme(c, dark, InfinitySchemeStyle.VIBRANT) },
        "忠实" to { c -> seededColorScheme(c, dark, InfinitySchemeStyle.FIDELITY) },
        "表现力" to { c -> seededColorScheme(c, dark, InfinitySchemeStyle.EXPRESSIVE) },
        "明亮(新)" to { c -> brightColorScheme(c, dark) },
    )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "五种配色风格主色对照（原色相保真 vs 饱和活力）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Spacer(Modifier.width(42.dp))
                styles.forEach { (label, _) ->
                    val highlight = label.startsWith("明亮")
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                }
            }
            seeds.forEach { (name, seed) ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(36.dp),
                    )
                    styles.forEach { (_, schemeOf) ->
                        val sc = schemeOf(seed)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(sc.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Add, null, tint = sc.onPrimary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrightThemeAndHeadersBody(dark: Boolean) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. 统一页头主按钮对照（对话页 & 资料页均采用 34dp 圆形 primary / onPrimary + 按钮）
            Text(
                text = "页头统一圆形 + 按钮（同源 primary / onPrimary）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 58.dp)
                            .padding(start = 16.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("对话", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        }
                        UnifiedHeaderCircleAddButton()
                    }
                    InfinityGroupDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 58.dp)
                            .padding(start = 16.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("资料", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        UnifiedHeaderCircleAddButton()
                    }
                }
            }

            // 2. 五种配色风格跨种子色对比
            SchemeSwatchComparisonCard(dark = dark)

            // 3. 外观 → 配色风格选择列表（新增第 5 项「明亮」）
            InfinitySettingsSection(title = "配色风格（外观设置）") {
                val styleItems = listOf(
                    Triple(Icons.Rounded.Palette, "柔和", "压低饱和度，整体沉稳。"),
                    Triple(Icons.Rounded.ColorLens, "鲜艳", "强调色保持高饱和。"),
                    Triple(Icons.Rounded.Brush, "忠实", "尽量贴近你选的颜色。"),
                    Triple(Icons.Rounded.AutoAwesome, "表现力", "辅色偏移色相，对比更强。"),
                    Triple(Icons.Rounded.WbSunny, "明亮", "锁定原色相，呈现高饱和活力亮色。"),
                )
                styleItems.forEachIndexed { idx, (icon, title, desc) ->
                    val selected = idx == 4
                    InfinitySettingsItem(
                        icon = icon,
                        title = title,
                        subtitle = desc,
                        onClick = {},
                        trailingContent = {
                            if (selected) {
                                Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                        },
                    )
                }
            }

            // 4. 明亮风格下常见控件效果
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InfinityPillButton(text = "主按钮操作", onClick = {}, tone = InfinityPillTone.PRIMARY, icon = Icons.Rounded.Check)
                InfinityPillButton(text = "次要操作", onClick = {}, tone = InfinityPillTone.NEUTRAL)
                Spacer(Modifier.weight(1f))
                Switch(checked = true, onCheckedChange = {})
            }
        }
    }
}

// =========================================================================
// 第二批：内置代理（裁剪版 mihomo）设置页、导入弹层、YAML 编辑器与提供商路由
// =========================================================================

private data class ProtoProxyNode(
    val name: String,
    val protocol: String,
    val source: String,
    val latencyMs: Int?, // null 表示超时/不可用
    val enabled: Boolean = true,
    val selected: Boolean = false,
)

private val SAMPLE_NODES = listOf(
    ProtoProxyNode("HK-IPLC-01", "VLESS · Reality", "机场主订阅", latencyMs = 42, enabled = true, selected = true),
    ProtoProxyNode("JP-Tokyo-02", "Hysteria2", "机场主订阅", latencyMs = 68, enabled = true),
    ProtoProxyNode("SG-Direct-03", "Trojan", "手动导入", latencyMs = 135, enabled = true),
    ProtoProxyNode("US-West-Backup", "Shadowsocks", "机场主订阅", latencyMs = null, enabled = false),
)

@Composable
private fun ProxyStatusCard(activeNode: String, latencyMs: Int, port: Int, modeLabel: String) {
    val domain = MaterialTheme.infinityDomainColors
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(0.75.dp, scheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(domain.signalOn))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("运行中", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                        ProductionStatusChip(modeLabel, ProductionTone.INFO)
                    }
                    Text(
                        text = "当前出口：$activeNode · ${latencyMs} ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (domain.isDark) InfinityObsidianDialogChip else scheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "127.0.0.1:$port · HTTP / SOCKS5（已认证）",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            InfinityPillRow {
                InfinityPillButton(text = "一键测速", onClick = {}, icon = Icons.Rounded.Speed, compact = true)
                InfinityPillButton(text = "高级 YAML", onClick = {}, icon = Icons.Rounded.Code, compact = true)
            }
        }
    }
}

@Composable
private fun ProxyNodeRow(
    node: ProtoProxyNode,
    rank: Int?,
    fallbackMode: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val latencyColor = when {
        !node.enabled -> scheme.onSurfaceVariant
        node.latencyMs == null -> scheme.error
        node.latencyMs < 100 -> domain.signalOn
        else -> domain.signalPending
    }
    val latencyText = when {
        !node.enabled -> "已停用"
        node.latencyMs == null -> "超时"
        else -> "${node.latencyMs} ms"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (node.enabled) 1f else 0.58f)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (fallbackMode) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (rank == 1 && node.enabled) scheme.primary else scheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (rank != null && node.enabled) "#$rank" else "—",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (rank == 1 && node.enabled) scheme.onPrimary else scheme.onSurfaceVariant,
                )
            }
        } else {
            Icon(
                imageVector = if (node.selected && node.enabled) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (node.selected && node.enabled) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (node.selected || rank == 1) FontWeight.SemiBold else FontWeight.Normal,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = latencyText,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                    color = latencyColor,
                    maxLines = 1,
                )
            }
            Text(
                text = "${node.protocol} · ${node.source}",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (fallbackMode && node.enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(scheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.ArrowUpward, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(scheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.ArrowDownward, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        }
        Switch(checked = node.enabled, onCheckedChange = {})
    }
}

@Composable
private fun ProxySettingsBody(fallbackMode: Boolean, showBack: Boolean = true) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ProtoNavTopBar("内置代理", showBack = showBack) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Speed, null, tint = MaterialTheme.colorScheme.onSurface)
                }
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            Column(
                Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                InfinitySettingsSection(
                    title = "服务与状态",
                    footer = "独立用户态代理核心，无需系统 VPN，不干扰 Tailscale P2P 直连。",
                ) {
                    SettingsToggle(
                        title = "启用内置代理",
                        description = "仅监听本机回环并随机认证",
                        checked = true,
                        onCheckedChange = {},
                        icon = Icons.Rounded.Public,
                    )
                }

                ProxyStatusCard(
                    activeNode = "HK-IPLC-01",
                    latencyMs = 42,
                    port = 41823,
                    modeLabel = if (fallbackMode) "顺序降级" else "单节点",
                )
                Spacer(Modifier.height(InfinitySpacing.SectionGap))

                InfinitySettingsSection(
                    title = "出站模式",
                    footer = if (fallbackMode) {
                        "按已启用节点的先后顺序优先使用首个可用节点，探活失败自动降级至下一个；不含分流规则，回环/局域网与 Tailnet 始终直连。"
                    } else {
                        "仅使用当前选中的单个已启用节点转发公网流量；不含分流规则，回环/局域网与 Tailnet 始终直连。"
                    },
                ) {
                    InfinitySegmentedControl(
                        segments = listOf(
                            InfinitySegment(false, "单节点"),
                            InfinitySegment(true, "顺序降级"),
                        ),
                        selected = fallbackMode,
                        onSelect = {},
                        fillWidth = true,
                        contentPadding = PaddingValues(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small),
                    )
                }

                InfinitySettingsSection(title = "订阅与导入") {
                    InfinitySettingsItem(
                        icon = Icons.Rounded.RssFeed,
                        title = "机场主订阅",
                        subtitle = "18 个节点 · 12 分钟前更新",
                        onClick = {},
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Refresh, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                Icon(Icons.Rounded.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                        },
                    )
                    InfinityGroupDivider()
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Add,
                        title = "导入订阅或节点链接",
                        subtitle = "支持 Clash YAML / Base64 订阅及主流分享链接",
                        subtitleMaxLines = 2,
                        onClick = {},
                        showChevron = true,
                    )
                }

                InfinitySettingsSection(
                    title = if (fallbackMode) "节点列表（按顺序降级 · 3 已启用）" else "节点列表（单选 · 3 已启用）",
                ) {
                    var rank = 0
                    SAMPLE_NODES.forEachIndexed { index, node ->
                        if (index > 0) InfinityGroupDivider()
                        val nodeRank = if (node.enabled) ++rank else null
                        ProxyNodeRow(node = node, rank = nodeRank, fallbackMode = fallbackMode)
                    }
                }

                InfinitySettingsSection(title = "覆盖范围与 Linux 环境") {
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Dns,
                        title = "AI 模型提供商",
                        subtitle = "默认直连；在各提供商编辑页按需勾选代理",
                        subtitleMaxLines = 2,
                        onClick = {},
                    )
                    InfinityGroupDivider()
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Language,
                        title = "内置浏览器与 Agent 公网工具",
                        subtitle = "开启后自动生效，回环、局域网与 Tailnet 自动直连",
                        subtitleMaxLines = 2,
                        onClick = {},
                    )
                    InfinityGroupDivider()
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Terminal,
                        title = "Alpine Linux 虚拟环境",
                        subtitle = "不全局劫持；已注入 \$INFINITY_PROXY_HTTP 供按需使用",
                        subtitleMaxLines = 2,
                        onClick = {},
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ProxyImportAndYamlBody() {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // 上半部：L1 黑曜石导入弹层
            Text("导入弹层（订阅链接 / 节点分享链接）", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = InfinityObsidianSolidContainer,
                border = BorderStroke(0.75.dp, InfinityPureWhite.copy(alpha = 0.12f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .obsidianTopGlint(InfinityPureWhite.copy(alpha = 0.16f), 24.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("添加代理节点", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                    InfinitySegmentedControl(
                        segments = listOf(
                            InfinitySegment("sub", "订阅链接", leading = { Icon(Icons.Rounded.RssFeed, null, Modifier.size(16.dp)) }),
                            InfinitySegment("uri", "节点链接", leading = { Icon(Icons.Rounded.Link, null, Modifier.size(16.dp)) }),
                        ),
                        selected = "sub",
                        onSelect = {},
                        fillWidth = true,
                    )
                    OutlinedTextField(
                        value = "机场主订阅",
                        onValueChange = {},
                        label = { Text("订阅名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = "https://sub.example.com/api/v1/client/subscribe?token=••••",
                        onValueChange = {},
                        label = { Text("订阅地址（加密保存）") },
                        supportingText = { Text("支持 Clash YAML 与 Base64 节点列表；凭据不会写入日志。") },
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    InfinityPillRow {
                        InfinityPillButton(text = "取消", onClick = {}, tone = InfinityPillTone.NEUTRAL)
                        InfinityPillButton(text = "拉取并导入", onClick = {}, tone = InfinityPillTone.PRIMARY)
                    }
                }
            }

            // 下半部：高级模式 YAML 编辑器（复用内置文档编辑器架构）
            Text("高级模式 · 内置 YAML 编辑器（保存前经内核校验）", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = scheme.surfaceContainerLowest,
                border = BorderStroke(0.75.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = scheme.onSurface, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text("proxy-nodes.yaml", style = MaterialTheme.typography.titleSmall.copy(fontFamily = InfinityMonospace), color = scheme.onSurface)
                            Text("高级手动编辑 · 自动剥离 TUN 与分流规则", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        }
                        InfinityPillButton(text = "校验并保存", onClick = {}, tone = InfinityPillTone.PRIMARY, compact = true)
                    }
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f))
                    val yamlLines = listOf(
                        "proxies:",
                        "  - name: \"HK-IPLC-01\"",
                        "    type: vless",
                        "    server: hk01.example.net",
                        "    port: 443",
                        "    uuid: \"••••••••-••••-••••\"",
                        "    network: tcp",
                        "    tls: true",
                        "    flow: xtls-rprx-vision",
                        "    reality-opts:",
                        "      public-key: \"••••••••\"",
                    )
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        yamlLines.forEachIndexed { idx, line ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = "${idx + 1}".padStart(2, ' '),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace, fontSize = 11.sp),
                                    color = scheme.onSurfaceVariant.copy(alpha = 0.55f),
                                )
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace, fontSize = 12.sp),
                                    color = scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Surface(
                        color = domain.successContainer.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = domain.signalOn, modifier = Modifier.size(16.dp))
                            Text(
                                text = "内核语法与出站节点校验通过 · 3 个可用节点",
                                style = MaterialTheme.typography.labelSmall,
                                color = domain.onSuccessContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderProxyOptionCard(
    providerTitle: String,
    useProxy: Boolean,
    proxyRunning: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    InfinitySettingsSection(title = "接口 · $providerTitle") {
        InfinitySegmentedControl(
            segments = listOf(
                InfinitySegment(true, "公网", leading = { Icon(Icons.Rounded.Language, null, Modifier.size(18.dp)) }),
                InfinitySegment(false, "Tailscale 内网", leading = { Icon(Icons.Rounded.Lan, null, Modifier.size(18.dp)) }),
            ),
            selected = true,
            onSelect = {},
            fillWidth = true,
            contentPadding = PaddingValues(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small),
        )
        InfinityGroupDivider()
        SettingsToggle(
            title = "经内置代理连接",
            description = if (!useProxy) "默认直连，适合国内或无需代理的服务商" else "仅该服务商的对话、模型与测连经内置代理",
            checked = useProxy,
            onCheckedChange = {},
            icon = Icons.Rounded.Public,
        )
        if (useProxy) {
            InfinityGroupDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (proxyRunning) domain.signalOn else domain.signalPending),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (proxyRunning) "内置代理运行中" else "内置代理未开启",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                    )
                    Text(
                        text = if (proxyRunning) "当前出口 HK-IPLC-01 · 42 ms" else "请求会直接失败且不回退直连，请先启用",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (proxyRunning) scheme.onSurfaceVariant else domain.warning,
                    )
                }
                InfinityPillButton(
                    text = if (proxyRunning) "设置" else "去开启",
                    onClick = {},
                    compact = true,
                    tone = if (proxyRunning) InfinityPillTone.NEUTRAL else InfinityPillTone.PRIMARY,
                )
            }
        }
    }
}

@Composable
private fun ProviderFormProxyStatesBody() {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ProviderProxyOptionCard("DeepSeek（默认直连）", useProxy = false, proxyRunning = true)
            ProviderProxyOptionCard("OpenAI（勾选内置代理 · 正常运行）", useProxy = true, proxyRunning = true)
            ProviderProxyOptionCard("Anthropic（勾选内置代理 · 代理未开）", useProxy = true, proxyRunning = false)
        }
    }
}

@Composable
private fun InnerTwoPaneProxyBody() {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            // 左列：设置导航（执行环境与连接分组）
            Column(
                Modifier
                    .width(310.dp)
                    .fillMaxHeight()
                    .background(scheme.surface)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("设置", style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Text("执行环境与连接", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                Surface(shape = RoundedCornerShape(18.dp), color = scheme.surfaceContainerLow) {
                    Column {
                        InfinitySettingsItem(icon = Icons.Rounded.Hub, title = "Tailscale 内网", subtitle = "已连接 · 3 台设备", onClick = {}, showChevron = true)
                        InfinityGroupDivider()
                        Box(Modifier.background(scheme.primaryContainer.copy(alpha = 0.45f))) {
                            InfinitySettingsItem(icon = Icons.Rounded.Public, title = "内置代理", subtitle = "运行中 · HK-IPLC-01 · 42 ms", onClick = {}, showChevron = true)
                        }
                        InfinityGroupDivider()
                        InfinitySettingsItem(icon = Icons.Rounded.Language, title = "网络访问", subtitle = "Tailscale + 内置代理", onClick = {}, showChevron = true)
                        InfinityGroupDivider()
                        InfinitySettingsItem(icon = Icons.Rounded.VpnKey, title = "SSH 主机与终端", subtitle = "2 台主机", onClick = {}, showChevron = true)
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant.copy(alpha = 0.4f)))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                ProxySettingsBody(fallbackMode = true, showBack = false)
            }
        }
    }
}

// =========================================================================
// 7 组 Compose Preview 入口（供 Android Compose Preview 侧栏直接渲染）
// =========================================================================

@Preview(name = "1. 明亮风格对照与统一圆形+按钮（深色 411）", widthDp = 411, heightDp = 960, locale = "zh-rCN")
@Composable
fun BrightThemeAndHeadersDarkPreview() = BrightProtoTheme(dark = true) {
    BrightThemeAndHeadersBody(dark = true)
}

@Preview(name = "2. 明亮风格对照与统一圆形+按钮（浅色 411）", widthDp = 411, heightDp = 960, locale = "zh-rCN")
@Composable
fun BrightThemeAndHeadersLightPreview() = BrightProtoTheme(dark = false) {
    BrightThemeAndHeadersBody(dark = false)
}

@Preview(name = "3. 内置代理设置页 顺序降级模式（深色 411）", widthDp = 411, heightDp = 1520, locale = "zh-rCN")
@Composable
fun ProxySettingsFallbackDarkPreview() = BrightProtoTheme(dark = true) {
    ProxySettingsBody(fallbackMode = true)
}

@Preview(name = "4. 内置代理设置页 单节点模式（浅色 360 1.15x）", widthDp = 360, heightDp = 1600, locale = "zh-rCN", fontScale = 1.15f)
@Composable
fun ProxySettingsSingleNodeLightPreview() = BrightProtoTheme(dark = false) {
    ProxySettingsBody(fallbackMode = false)
}

@Preview(name = "5. 订阅/节点导入弹层与高级 YAML 编辑器（深色 411）", widthDp = 411, heightDp = 1040, locale = "zh-rCN")
@Composable
fun ProxyImportAndYamlEditorDarkPreview() = BrightProtoTheme(dark = true) {
    ProxyImportAndYamlBody()
}

@Preview(name = "6. 提供商编辑页直连/内置代理三态（深色 411）", widthDp = 411, heightDp = 960, locale = "zh-rCN")
@Composable
fun ProviderFormProxyStatesDarkPreview() = BrightProtoTheme(dark = true) {
    ProviderFormProxyStatesBody()
}

@Preview(name = "7. 展开内屏双栏 设置→内置代理（深色 900）", widthDp = 900, heightDp = 860, locale = "zh-rCN")
@Composable
fun InnerTwoPaneProxyDarkPreview() = BrightProtoTheme(dark = true) {
    InnerTwoPaneProxyBody()
}
