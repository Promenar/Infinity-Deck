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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.obsidianTopGlint
import com.promenar.infinity.ui.production.ProductionStatusChip
import com.promenar.infinity.ui.production.ProductionTone
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityIceBlueSeed
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityObsidianDialogChip
import com.promenar.infinity.ui.theme.InfinityObsidianMenuContainer
import com.promenar.infinity.ui.theme.InfinitySchemeStyle
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.infinityDomainColors

/*
 * 顺序降级模式手动拖拽排序高保真视觉原型（v1，仅 debug 源码，待用户确认后接入 ProxySettingsScreen）：
 *
 * 交互设计要点：
 * 1. 右侧外缘配置专用拖拽把手（Icons.Rounded.DragHandle，36×44dp 触控区），替换原有的 ↑ / ↓ 两个小圆钮，
 *    释放 24dp 水平空间避免窄屏/大字号下长节点名被挤断，且贴右缘单手拇指易操作、不误触 Switch。
 * 2. 双重拖拽触发：直接按住右侧 ≡ 把手即刻上下拖（零延迟、不抢页面纵向滚动）；或长按节点行任意区域后上下拖（带轻微触感震动）。
 * 3. 拖拽过程：节点浮起（zIndex 抬高、1.015x 微缩放、阴影 + 主色 1dp 圆角细边、≡ 染主色），跨过相邻行中线时
 *    本地列表即时换位并实时刷新 #1/#2/#3 优先级徽章，邻近视口顶底边自动平滑滚动；松手后仅提交一次 moveNode(id, delta) 与内核重载。
 * 4. 快捷精准操作与无障碍：短按节点打开黑曜石菜单保留「测速 / 移至首位 / 上移一位 / 下移一位 / 删除节点」，
 *    同时为 TalkBack 挂载上移/下移无障碍动作。
 */

private data class DragProtoNode(
    val id: String,
    val name: String,
    val protocol: String,
    val source: String,
    val latencyMs: Int?,
    val enabled: Boolean = true,
)

private val DRAG_PROTO_IDLE_NODES = listOf(
    DragProtoNode("hk", "HK-IPLC-Premium-01", "VLESS", "主订阅", latencyMs = 38, enabled = true),
    DragProtoNode("jp", "JP-Tokyo-HY2-02", "Hysteria2", "主订阅", latencyMs = 64, enabled = true),
    DragProtoNode("sg", "SG-Direct-Trojan-03", "Trojan", "手动导入", latencyMs = 128, enabled = true),
    DragProtoNode("us", "US-West-Backup-04", "Shadowsocks", "主订阅", latencyMs = null, enabled = false),
)

private val DRAG_PROTO_ACTIVE_NODES = listOf(
    DragProtoNode("hk", "HK-IPLC-Premium-01", "VLESS", "主订阅", latencyMs = 38, enabled = true),
    DragProtoNode("sg", "SG-Direct-Trojan-03", "Trojan", "手动导入", latencyMs = 128, enabled = true),
    DragProtoNode("jp", "JP-Tokyo-HY2-02", "Hysteria2", "主订阅", latencyMs = 64, enabled = true),
    DragProtoNode("us", "US-West-Backup-04", "Shadowsocks", "主订阅", latencyMs = null, enabled = false),
)

@Composable
private fun DragProtoTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(
        darkTheme = darkTheme,
        preferences = InfinityThemePreferences(
            mode = if (darkTheme) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
            primaryColor = InfinityIceBlueSeed.value.toLong(),
            schemeStyle = InfinitySchemeStyle.BRIGHT,
            pureBlack = darkTheme,
        ),
        content = content,
    )
}

@Composable
private fun DragProtoNodeRow(
    node: DragProtoNode,
    rank: Int?,
    activeExit: Boolean,
    dragging: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val latencyText = when {
        !node.enabled -> "已停用"
        node.latencyMs == null -> "超时"
        else -> "${node.latencyMs} ms"
    }
    val latencyColor = when {
        !node.enabled -> scheme.onSurfaceVariant
        node.latencyMs == null -> scheme.error
        node.latencyMs < 100 -> domain.signalOn
        else -> domain.signalPending
    }

    val dragBg = if (domain.isDark) InfinityObsidianDialogChip else scheme.surfaceContainerHighest
    val rowShape = RoundedCornerShape(14.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 2f else 0f)
            .graphicsLayer {
                if (dragging) {
                    translationY = -6.dp.toPx()
                    scaleX = 1.01f
                    scaleY = 1.01f
                }
            }
            .then(
                if (dragging) {
                    Modifier
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .shadow(elevation = 10.dp, shape = rowShape, clip = false)
                        .clip(rowShape)
                        .background(dragBg)
                        .border(1.dp, scheme.primary.copy(alpha = 0.72f), rowShape)
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (node.enabled || dragging) 1f else 0.58f)
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val highlightRank = activeExit && node.enabled
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (highlightRank) scheme.primary else scheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (rank != null && node.enabled) "#$rank" else "—",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (highlightRank) scheme.onPrimary else scheme.onSurfaceVariant,
                )
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = node.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (highlightRank || dragging) FontWeight.SemiBold else FontWeight.Normal,
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
                InfinityMetaLine(
                    segments = listOf(node.protocol, node.source),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            Switch(
                checked = node.enabled,
                onCheckedChange = {},
            )

            Box(
                modifier = Modifier
                    .size(width = 34.dp, height = 40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (dragging) scheme.primary.copy(alpha = 0.16f) else scheme.surfaceContainerHighest.copy(alpha = 0f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.DragHandle,
                    contentDescription = "拖动调整顺序",
                    tint = if (dragging) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.78f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun DragProtoPage(
    nodes: List<DragProtoNode>,
    draggingNodeId: String? = null,
    showTapMenuPreview: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val activeId = nodes.firstOrNull { it.enabled }?.id

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = scheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = 14.dp),
        ) {
            // 顶部紧凑状态卡（上下文锚定）
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = scheme.surfaceContainerLow,
                border = BorderStroke(0.75.dp, scheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = InfinitySpacing.SectionGap),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(domain.signalOn))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("运行中", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                                ProductionStatusChip("顺序降级", ProductionTone.INFO)
                            }
                            Text(
                                text = "当前出口：${nodes.firstOrNull { it.enabled }?.name.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    InfinityPillRow {
                        InfinityPillButton(text = "一键测速", onClick = {}, icon = Icons.Rounded.Speed, compact = true)
                        InfinityPillButton(text = "高级 YAML", onClick = {}, icon = Icons.Rounded.Code, compact = true)
                    }
                }
            }

            // 出站模式
            InfinitySettingsSection(title = "出站模式") {
                InfinitySegmentedControl(
                    segments = listOf(
                        InfinitySegment("single", "单节点"),
                        InfinitySegment("fallback", "顺序降级"),
                    ),
                    selected = "fallback",
                    onSelect = {},
                    fillWidth = true,
                    contentPadding = PaddingValues(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small),
                )
            }

            // 节点列表（顺序降级 + 拖拽排序）
            InfinitySettingsSection(
                title = "节点列表（顺序降级，${nodes.count { it.enabled }} 已启用）",
                footer = "按住右侧拖拽把手或长按节点上下拖动可调整降级顺序；点按节点可测速、置顶或删除。",
            ) {
                var rank = 0
                nodes.forEachIndexed { index, node ->
                    val isDragging = node.id == draggingNodeId
                    val prevDragging = index > 0 && nodes[index - 1].id == draggingNodeId
                    if (index > 0 && !isDragging && !prevDragging) {
                        InfinityGroupDivider()
                    }
                    val nodeRank = if (node.enabled) ++rank else null
                    DragProtoNodeRow(
                        node = node,
                        rank = nodeRank,
                        activeExit = node.id == activeId,
                        dragging = isDragging,
                    )
                }
            }

            if (showTapMenuPreview) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "点按节点行展开快捷菜单（精准单步 / 一键置顶）",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                )
                val menuShape = RoundedCornerShape(16.dp)
                val menuContainer = if (domain.isDark) InfinityObsidianMenuContainer else scheme.surfaceContainerHigh
                Surface(
                    shape = menuShape,
                    color = menuContainer,
                    border = BorderStroke(0.75.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .width(204.dp)
                        .padding(start = 16.dp)
                        .then(if (domain.isDark) Modifier.obsidianTopGlint(Color.White.copy(alpha = 0.14f), 16.dp) else Modifier),
                ) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        ProtoMenuRow(Icons.Rounded.Speed, "测速", destructive = false)
                        ProtoMenuRow(Icons.Rounded.VerticalAlignTop, "移至首位", destructive = false)
                        ProtoMenuRow(Icons.Rounded.ArrowUpward, "上移一位", destructive = false)
                        ProtoMenuRow(Icons.Rounded.ArrowDownward, "下移一位", destructive = false)
                        ProtoMenuRow(Icons.Rounded.Delete, "删除节点", destructive = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtoMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    destructive: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (destructive) scheme.error else scheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (destructive) scheme.error else scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Preview(name = "1. 静息态 + 点按菜单（深色 360dp）", widthDp = 360, heightDp = 960)
@Composable
fun ProxyFallbackDragIdleDarkPreview() {
    DragProtoTheme(darkTheme = true) {
        DragProtoPage(
            nodes = DRAG_PROTO_IDLE_NODES,
            draggingNodeId = null,
            showTapMenuPreview = true,
        )
    }
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Preview(name = "2. 拖拽浮起换位态（深色 360dp）", widthDp = 360, heightDp = 680)
@Composable
fun ProxyFallbackDragActiveDarkPreview() {
    DragProtoTheme(darkTheme = true) {
        DragProtoPage(
            nodes = DRAG_PROTO_ACTIVE_NODES,
            draggingNodeId = "sg",
            showTapMenuPreview = false,
        )
    }
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Preview(name = "3. 浅色大字号 1.3x 拖拽态（360dp）", widthDp = 360, heightDp = 760, fontScale = 1.3f)
@Composable
fun ProxyFallbackDragLightLargeFontPreview() {
    DragProtoTheme(darkTheme = false) {
        DragProtoPage(
            nodes = DRAG_PROTO_ACTIVE_NODES,
            draggingNodeId = "sg",
            showTapMenuPreview = false,
        )
    }
}
